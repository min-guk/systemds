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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.InputBindingReceipt;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResults;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Real compiler graph: private aggregate sources, released row sums, and exact supply receipts. */
@net.jcip.annotations.NotThreadSafe
public class ExactJointBindingIntegrationTest {
	@Test
	public void globalSearchMatchesCompleteCatalogAndPreservesBindings() throws Exception {
		PlacementAnalysis analysis = analysis();
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		List<ExactCategoricalSolver.Factor> packedFactors = new ArrayList<>(model.hardFactors());
		packedFactors.addAll(surface.exactSolverFactors());
		var reference = ExactPhysicalReducedSolver.solve(model.variables().size(),
			surface.exactSolverVariables(), packedFactors, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		var optimized = ExactPhysicalOptimizer.optimize(model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		Assert.assertEquals(Double.doubleToRawLongBits(reference.objective()), optimized.canonicalObjectiveBits());
		Assert.assertNotNull("Global must return the actual searched binding certificate", optimized.inputBindings());
		Assert.assertFalse(optimized.inputBindings().isEmpty());
		Assert.assertNotNull(optimized.jointSearch());
		Assert.assertEquals(model.domains().size(), optimized.jointSearch().operatorVariables());
		Assert.assertTrue(optimized.jointSearch().bindingVariables() > 0);
		Assert.assertEquals(optimized.solverResult().assignmentInVariableOrder(),
			ExactPhysicalBindingModel.build(model).reconstruct(optimized.jointSearch().assignment()));
		var selected = ExactPhysicalSelection.create(model, optimized);
		Assert.assertEquals(optimized.inputBindings(), selected.inputBindings());
		var projected = ExactPhysicalPlacementProjector.project(selected).normalizedResult();
		Assert.assertEquals(selected.relocationChoices(), projected.selectedRelocationChoices());
		Assert.assertEquals(selected.localMaterializations(), projected.selectedLocalMaterializations());
		Assert.assertEquals(selected.inputBindingReceipts(), projected.selectedInputBindings());
		Assert.assertTrue(projected.objectiveCertificate().contains(selected.bindingFingerprint()));
		var tampered = new ArrayList<>(optimized.inputBindings());
		tampered.remove(tampered.size() - 1);
		IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class, () ->
			ExactPhysicalSelection.create(model, new ExactPhysicalOptimizer.Result(optimized.solverResult(),
				optimized.canonicalObjectiveBits(), optimized.contributionFingerprint(), tampered)));
		Assert.assertEquals("EXACT_PHYSICAL_SELECTED_BINDING_MISMATCH", error.getMessage());
		Assert.assertThrows("An actual joint search must retain its binding certificate",
			IllegalArgumentException.class, () -> new ExactPhysicalOptimizer.Result(optimized.solverResult(),
				optimized.canonicalObjectiveBits(), optimized.contributionFingerprint(), null, optimized.jointSearch()));
		var foreignOrdinal = new ArrayList<>(selected.inputBindingReceipts());
		var binding = foreignOrdinal.get(0);
		foreignOrdinal.set(0, new InputBindingReceipt(binding.ownerKind(), binding.sourceOccurrence(),
			binding.sourceValueVersion(), binding.consumerOccurrence(), binding.inputPosition(), Integer.MAX_VALUE,
			binding.supplyKind(), binding.relocationAction(), binding.localMaterializationAction()));
		Assert.assertThrows("Binding ordinals must identify a real analysis-owned use",
			IllegalArgumentException.class, () -> NormalizedPlannerResults.createWithPhysicalSelections(
				analysis, "Exact", selected.selectedEmissionStates(), selected.candidateReceipts(),
				selected.relocationChoices(), selected.localMaterializations(), foreignOrdinal, "invalid-use-ordinal"));
	}

	@Test
	public void fixedOperatorsCanChooseEitherCommonAnchorAndCommitThatBinding() throws Exception {
		PlacementAnalysis analysis = analysis();
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var joint = ExactPhysicalBindingModel.build(model);
		var consumer = joint.operatorDomains().stream().filter(domain ->
			analysis.hop(domain.node().key()).map(hop -> "C".equals(hop.getName())).orElse(false))
			.findFirst().orElseThrow();
		int operator = -1;
		for(int value = 0; value < consumer.choices().size(); value++) {
			var alternative = consumer.choices().get(value).representative();
			if(alternative.state().execType() == ExecType.FED && alternative.orderedInputs().size() == 2
				&& alternative.orderedInputs().stream().allMatch(PlacementAnalysis.CandidateInputState::present)) {
				operator = value;
				break;
			}
		}
		Assert.assertTrue("Released row sums must admit a FED consumer with two physical inputs", operator >= 0);
		var anchors = analysis.graph().relocationActions().stream()
			.filter(action -> action.obligations().stream().anyMatch(obligation ->
				obligation.consumer() == consumer.node().key()))
			.map(action -> action.key().durableAnchor()).distinct().sorted().toList();
		Assert.assertTrue("There must be multiple authorized materialization destinations", anchors.size() >= 2);
		List<ExactCategoricalSolver.Factor> packedFactors = new ArrayList<>(model.hardFactors());
		packedFactors.addAll(surface.exactSolverFactors());
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>(joint.translateFactors(packedFactors));
		factors.add(force(consumer.variable(), operator));
		var variables = joint.solverVariables(surface.exactSolverVariables());
		var first = ExactPhysicalReducedSolver.solve(joint.variables().size(), variables, factors,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		for(var domain : joint.operatorDomains())
			factors.add(force(domain.variable(), first.assignmentInVariableOrder().get(
				joint.variables().indexOf(domain.variable()))));
		List<String> fingerprints = new ArrayList<>();
		for(var anchor : anchors.subList(0, 2)) {
			List<ExactCategoricalSolver.Factor> forced = new ArrayList<>(factors);
			for(var use : joint.bindingDomains()) {
				if(use.use().consumer() != consumer.node().key())
					continue;
				double[] allowed = new double[use.choices().size()];
				for(int value = 0; value < allowed.length; value++) {
					var binding = use.choices().get(value);
					allowed[value] = binding.active() && binding.authority().relocationAction() != null
						&& binding.authority().relocationAction().key().durableAnchor().equals(anchor)
						? 0d : Double.POSITIVE_INFINITY;
				}
				forced.add(ExactCategoricalSolver.Factor.dense(List.of(use.variable()), allowed));
			}
			var solved = ExactPhysicalReducedSolver.solve(joint.variables().size(), variables, forced,
				ExactPhysicalOptimizer.PRODUCTION_LIMITS);
			var assignment = solved.assignmentInVariableOrder().subList(0, joint.variables().size());
			var packed = joint.reconstruct(assignment);
			long canonical = surface.evaluateCanonical(packed);
			Assert.assertEquals(canonical, Double.doubleToRawLongBits(solved.objective()));
			var selected = ExactPhysicalSelection.create(model, new ExactPhysicalOptimizer.Result(
				new ExactCategoricalSolver.Result(solved.objective(), packed, solved.statistics()),
				canonical, surface.contributionFingerprint(), joint.bindings(assignment)));
			var projected = ExactPhysicalPlacementProjector.project(selected).normalizedResult();
			Assert.assertEquals(selected.relocationChoices(), projected.selectedRelocationChoices());
			Assert.assertTrue(selected.inputBindings().stream().filter(binding ->
				binding.consumer() == consumer.node().key()).allMatch(binding ->
					binding.authority().relocationAction().key().durableAnchor().equals(anchor)));
			fingerprints.add(selected.bindingFingerprint());
			if(!selected.localMaterializations().isEmpty())
				Assert.assertThrows("Normalization must not repair missing selected local copies",
					IllegalArgumentException.class, () -> NormalizedPlannerResults.createWithPhysicalSelections(
						analysis, "Exact", selected.selectedEmissionStates(), selected.candidateReceipts(),
						selected.relocationChoices(), List.of(), "missing-selected-local-copy"));
		}
		Assert.assertNotEquals("Same operator assignment must retain different complete-plan bindings",
			fingerprints.get(0), fingerprints.get(1));

		// Both anchors above are feasible under the same X. A cost on Y alone must
		// choose the preferred alternative without fixing Y through a hard constraint.
		List<ExactCategoricalSolver.Factor> preference = new ArrayList<>(joint.translateFactors(model.hardFactors()));
		for(var domain : joint.operatorDomains())
			preference.add(force(domain.variable(), first.assignmentInVariableOrder().get(
				joint.variables().indexOf(domain.variable()))));
		var consumerUses = joint.bindingDomains().stream()
			.filter(use -> use.use().consumer() == consumer.node().key()).toList();
		Assert.assertEquals(2, consumerUses.size());
		for(var use : consumerUses)
			preference.add(anchorCosts(use, anchors.get(1), 7d));
		var preferred = ExactPhysicalReducedSolver.solve(joint.variables().size(), joint.variables(), preference,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		Assert.assertEquals("The binding objective reaches the nonnegative global lower bound", 0d,
			preferred.objective(), 0d);
		Assert.assertTrue(joint.bindings(preferred.assignmentInVariableOrder()).stream()
			.filter(binding -> binding.consumer() == consumer.node().key()).allMatch(binding ->
				binding.authority().relocationAction().key().durableAnchor().equals(anchors.get(1))));

		List<ExactCategoricalSolver.Factor> conflicting = new ArrayList<>(factors);
		conflicting.add(anchorCosts(consumerUses.get(0), anchors.get(0), Double.POSITIVE_INFINITY));
		conflicting.add(anchorCosts(consumerUses.get(1), anchors.get(1), Double.POSITIVE_INFINITY));
		IllegalArgumentException incompatible = Assert.assertThrows(IllegalArgumentException.class, () ->
			ExactPhysicalReducedSolver.solve(joint.variables().size(), variables, conflicting,
				ExactPhysicalOptimizer.PRODUCTION_LIMITS));
		Assert.assertEquals("EXACT_VE_NO_FEASIBLE_ASSIGNMENT", incompatible.getMessage());
	}

	@Test
	public void committedPlanRetainsUseBindingsAndRejectsBindingOnlyTampering() throws Exception {
		Fixture fixture = fixture();
		var analysis = fixture.analysis();
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var selection = ExactPhysicalSelection.create(model, ExactPhysicalOptimizer.optimize(
			model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS));
		var carrier = ExactPhysicalPlacementProjector.project(selection);
		var plan = carrier.normalizedResult();
		var retained = plan.selectedInputBindings();
		Assert.assertFalse(retained.isEmpty());
		Assert.assertEquals(selection.inputBindingReceipts(), carrier.selectedInputBindings());
		NormalizedPlannerResult tampered = (NormalizedPlannerResult) java.lang.reflect.Proxy.newProxyInstance(
			NormalizedPlannerResult.class.getClassLoader(), new Class<?>[] {NormalizedPlannerResult.class},
			(proxy, method, arguments) -> method.getName().equals("selectedInputBindings")
				? retained.subList(0, retained.size() - 1) : method.invoke(plan, arguments));
		Assert.assertNotEquals("Canonical content hashing must cover B independently of the objective string",
			PlacementEmissionTransaction.canonicalPlanHash(plan),
			PlacementEmissionTransaction.canonicalPlanHash(tampered));
		try {
			PlacementEmissionTransaction.resetForTesting();
			Assert.assertThrows(IllegalStateException.class, () -> PlacementEmissionTransaction.emit(
				fixture.program(), tampered, PlacementEmissionTransaction.FailureInjector.none()));
			var receipt = PlacementEmissionTransaction.emit(fixture.program(), plan,
				PlacementEmissionTransaction.FailureInjector.none());
			Assert.assertTrue(receipt.applied());
			Assert.assertEquals(retained, carrier.withEmissionReceipt(receipt).selectedInputBindings());
			Assert.assertEquals(retained, PlacementEmissionTransaction.currentNormalizedResult(fixture.program())
				.selectedInputBindings());
		}
		finally {
			PlacementEmissionTransaction.resetForTesting();
			org.apache.sysds.lops.compile.FederatedRefedRegistry.clear();
			org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry.clear();
			org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry.clear();
		}
	}

	private static ExactCategoricalSolver.Factor anchorCosts(ExactPhysicalBindingModel.BindingDomain use,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey anchor, double otherCost) {
		double[] costs = new double[use.choices().size()];
		for(int value = 0; value < costs.length; value++) {
			var binding = use.choices().get(value);
			costs[value] = binding.active() && binding.authority().relocationAction() != null
				&& binding.authority().relocationAction().key().durableAnchor().equals(anchor) ? 0d : otherCost;
		}
		return ExactCategoricalSolver.Factor.dense(List.of(use.variable()), costs);
	}

	private static ExactCategoricalSolver.Factor force(ExactCategoricalSolver.Variable variable, int value) {
		double[] costs = new double[variable.domainSize()];
		Arrays.fill(costs, Double.POSITIVE_INFINITY);
		costs[value] = 0d;
		return ExactCategoricalSolver.Factor.dense(List.of(variable), costs);
	}

	private static PlacementAnalysis analysis() throws Exception {
		return fixture().analysis();
	}

	private record Fixture(DMLProgram program, PlacementAnalysis analysis) { }

	private static Fixture fixture() throws Exception {
		String ranges = "list(list(0,0),list(2,2),list(2,0),list(4,2))";
		String script = "A=federated(addresses=list(\"localhost:1234/A1\",\"localhost:1235/A2\"),ranges="
			+ ranges + ");\nB=federated(addresses=list(\"localhost:2234/B1\",\"localhost:2235/B2\"),ranges="
			+ ranges + ");\nU=rowSums(A); V=rowSums(B); C=U+V; write(C,\"target/joint-binding-output\");\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return new Fixture(program, CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program));
	}
}
