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
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput;
import org.apache.sysds.lops.Ctable;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.FEDInstructionParser;
import org.apache.sysds.runtime.instructions.fed.CtableFEDInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/** Exact cost and lowering coverage for CTABLE's runtime materialized secondary input. */
public class ExactCtableMaterializationCostTest {
	@After
	public void resetEmissionState() {
		PlacementEmissionTransaction.resetForTesting();
	}

	@Test
	public void alignedTwoFedCtableMaterializesSecondaryAndLowersLout() throws Exception {
		Fixture fixture = fixture(twoSourceScript("C=table(A,B);D=C+1;print(sum(D));"));
		ExactPhysicalModel.DecisionDomain table = tableDomains(fixture).get(0);
		int tableValue = directLout(table);
		Solved solved = solve(fixture, Map.of(table, tableValue));
		ExactPhysicalModel.Alternative selected = selected(solved, table);

		assertDirectRowInput(fixture, solved, selected, 0, "A");
		assertDirectRowInput(fixture, solved, selected, 1, "B");
		Assert.assertEquals(ExecType.FED, selected.state().execType());
		Assert.assertEquals(FederatedOutput.LOUT, selected.state().output());

		var gets = activeCtableSecondaryGets(fixture, solved.values(), List.of(table));
		Assert.assertEquals("the cold secondary input is one reusable coordinator materialization",
			1, gets.size());
		var get = gets.get(0);
		double getCost = materializationCost(fixture, solved.values(), get);
		double secondaryBytes = FederatedCostModel.getEffectiveOutputMemEstimate(
			fixture.analysis.hop(get.endpoints().get(0).producer()).orElseThrow());
		double expectedGet = FederatedCostModel.computeReusableMaterializationDownloadCost(
			secondaryBytes, FType.ROW, 2);
		Assert.assertEquals(expectedGet, getCost, 1e-12);

		TernaryOp ctable = (TernaryOp) fixture.analysis.hop(table.node().key()).orElseThrow();
		var layout = ExactPhysicalCostModel.executionLayout(fixture.analysis, selected, FType.ROW, 2);
		var preparation = PlacementCostSemantics.analysisAwareMixedFedLocalCost(fixture.analysis,
			table.node().key(), ctable.getInput(), selected.orderedInputs().stream()
				.map(input -> input.present() ? input.fType() : null).toList(), FType.ROW, 0,
			FederatedCostModel.getEffectiveOutputMemEstimate(ctable), 2, layout)
			.getInputPreparationCost();
		double put = FederatedCostModel.computeInBandUploadPayloadCost(secondaryBytes, FType.ROW, 2);
		Assert.assertTrue("CTABLE must retain its per-invocation sliced secondary PUT", preparation >= put);

		ExactPhysicalSelection selection = ExactPhysicalSelection.create(fixture.model,
			new ExactPhysicalOptimizer.Result(solved.result(),
				fixture.surface.evaluateCanonical(solved.values()), fixture.surface.contributionFingerprint()));
		ExactPlacementInput projected = ExactPhysicalPlacementProjector.project(selection);
		PlacementEmissionTransaction.emit(fixture.program, projected.normalizedResult(),
			PlacementEmissionTransaction.FailureInjector.none());
		var lop = ctable.constructLops();
		Assert.assertTrue(lop instanceof Ctable);
		Assert.assertEquals(ExecType.FED, lop.getExecType());
		String instruction = lop.getInstructions("A", "B", "weight", "C");
		Assert.assertTrue(instruction, instruction.endsWith("°LOUT"));
		Assert.assertTrue(FEDInstructionParser.parseSingleInstruction(instruction)
			instanceof CtableFEDInstruction);
	}

	@Test
	public void retainedAliasAndCpConsumerShareOneColdGet() throws Exception {
		Fixture fixture = fixture(twoSourceScript(
			"S=B+1;C=table(A,S);D=C+1;q=sum(S);print(sum(D));print(q);"), Privacy.PUBLIC);
		ExactPhysicalModel.DecisionDomain table = tableDomains(fixture).get(0);
		int tableValue = directLout(table);
		List<ExactPhysicalModel.DecisionDomain> aggregates = fixture.model.domains().stream()
			.filter(domain -> fixture.analysis.hop(domain.node().key()).orElse(null) instanceof AggUnaryOp)
			.toList();
		ExactPhysicalModel.DecisionDomain cpConsumer = aggregates.stream()
			.filter(domain -> fixture.analysis.hop(domain.node().key()).orElse(null)
				instanceof AggUnaryOp aggregate && aggregate.getName().equals("q"))
			.filter(domain -> cpAlternative(domain) >= 0).findFirst().orElseThrow(() ->
				new AssertionError("aggregates=" + aggregates.stream().map(domain -> {
					AggUnaryOp aggregate = (AggUnaryOp) fixture.analysis.hop(domain.node().key()).orElseThrow();
					return aggregate.getName() + "<-" + aggregate.getInput(0).getName() + ':'
						+ aggregate.getInput(0).getClass().getSimpleName();
					}).toList()));
		Solved solved = solve(fixture, Map.of(table, tableValue,
			cpConsumer, cpAlternative(cpConsumer)));

		List<ExactPhysicalCostModel.PhysicalTransferKey> gets =
			activeCtableSecondaryGets(fixture, solved.values(), List.of(table));
		Assert.assertEquals("the retained alias and its CP consumer must share one cold GET|gets=" + gets,
			1, gets.size());
		var endpoints = gets.get(0).endpoints();
		Assert.assertTrue(endpoints.stream().anyMatch(endpoint -> endpoint.consumer() == table.node().key()
			&& endpoint.inputPosition() == 1));
		Assert.assertEquals(ExecType.CP, selected(solved, cpConsumer).state().execType());

		var retainedVersion = gets.get(0).sourceValueVersion();
		List<ExactPhysicalCostModel.PhysicalTransferKey> allRetainedGets = fixture.surface.transferKeys().stream()
			.filter(key -> key.direction() == ExactPhysicalCostModel.Direction.DOWNLOAD
				&& key.boundaryMode() == ExactPhysicalCostModel.BoundaryMode.ANCHOR_TRANSFER
				&& key.sourceValueVersion().equals(retainedVersion)).toList();
		double retainedGetCost = allRetainedGets.stream()
			.mapToDouble(key -> materializationCost(fixture, solved.values(), key)).sum();
		double bytes = FederatedCostModel.getEffectiveOutputMemEstimate(
			fixture.analysis.hop(gets.get(0).endpoints().get(0).producer()).orElseThrow());
		double expected = FederatedCostModel.computeReusableMaterializationDownloadCost(bytes, FType.ROW, 2);
		Assert.assertEquals("all CTABLE/TWrite/TRead/CP demands on S must total one reusable GET",
			expected, retainedGetCost, 1e-12);
		Assert.assertEquals(1, allRetainedGets.stream()
			.filter(key -> materializationCost(fixture, solved.values(), key) > 0).count());
		var cpAlias = allRetainedGets.stream().filter(key -> key.endpoints().stream()
			.anyMatch(endpoint -> endpoint.consumer() == cpConsumer.node().key())).findFirst().orElseThrow();
		Assert.assertEquals("the CP TRead consumes the retained coordinator object without another GET",
			0, materializationCost(fixture, solved.values(), cpAlias), 0);
	}

	@Test
	public void distinctSecondarySourceObjectsDoNotMergeTheirColdGets() throws Exception {
		String script = fed("A", "A") + fed("B", "B") + fed("E", "E")
			+ "C1=table(A,B);C2=table(A,E);D1=C1+1;D2=C2+1;print(sum(D1)+sum(D2));";
		Fixture fixture = fixture(script);
		List<ExactPhysicalModel.DecisionDomain> tables = tableDomains(fixture);
		Assert.assertEquals(2, tables.size());
		Map<ExactPhysicalModel.DecisionDomain,Integer> pins = new IdentityHashMap<>();
		for(var table : tables)
			pins.put(table, directLout(table));
		Solved solved = solve(fixture, pins);
		List<ExactPhysicalCostModel.PhysicalTransferKey> gets =
			activeCtableSecondaryGets(fixture, solved.values(), tables);
		Assert.assertEquals("different MatrixObjects on identical workers retain separate GETs", 2, gets.size());
		Assert.assertEquals(2, gets.stream().map(ExactPhysicalCostModel.PhysicalTransferKey::sourceValueVersion)
			.distinct().count());
		Assert.assertTrue(gets.stream().allMatch(get -> materializationCost(fixture, solved.values(), get) > 0));
	}

	@Test
	public void loopReusesOneColdGetWhileCtableInvocationCostScales() throws Exception {
		LoopCost once = loopCost(1);
		LoopCost twice = loopCost(2);
		LoopCost ten = loopCost(10);
		Assert.assertEquals(once.get(), twice.get(), 1e-12);
		Assert.assertEquals(once.get(), ten.get(), 1e-12);
		Assert.assertEquals(2 * once.invocation(), twice.invocation(), 1e-9);
		Assert.assertEquals(10 * once.invocation(), ten.invocation(), 1e-9);
	}

	private static LoopCost loopCost(int iterations) throws Exception {
		Fixture fixture = fixture(twoSourceScript("s=0;for(i in 1:" + iterations
			+ "){C=table(A,B);D=C+1;s=s+sum(D);}print(s);"));
		ExactPhysicalModel.DecisionDomain table = tableDomains(fixture).get(0);
		Solved solved = solve(fixture, Map.of(table, directLout(table)));
		List<ExactPhysicalCostModel.PhysicalTransferKey> gets =
			ctableSecondaryGets(fixture, List.of(table));
		Assert.assertFalse("the loop must retain a CTABLE secondary GET candidate", gets.isEmpty());
		Assert.assertEquals("all loop GET candidates must refer to the same retained B object", 1,
			gets.stream().map(ExactPhysicalCostModel.PhysicalTransferKey::sourceValueVersion).distinct().count());
		double getCost = materializationCost(fixture, solved.values(), gets);
		double secondaryBytes = FederatedCostModel.getEffectiveOutputMemEstimate(
			fixture.analysis.hop(gets.get(0).endpoints().get(0).producer()).orElseThrow());
		double expectedGet = FederatedCostModel.computeReusableMaterializationDownloadCost(
			secondaryBytes, FType.ROW, 2);
		Assert.assertEquals("the loop retains exactly one independently priced cold GET",
			expectedGet, getCost, 1e-12);
		double invocation = fixture.surface.contributions().stream()
			.filter(contribution -> contribution.factor().scope().equals(List.of(table.variable())))
			.mapToDouble(contribution -> fixture.surface.evaluateContributionCanonical(
				contribution, solved.values())).sum();
		Assert.assertTrue("the selected CTABLE invocation must carry positive per-call work",
			invocation > 0);
		return new LoopCost(getCost, invocation);
	}

	private static Fixture fixture(String script) throws Exception {
		return fixture(script, Privacy.PRIVATE_AGGREGATE);
	}

	private static Fixture fixture(String script, Privacy privacy) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
			.bindAtFinalHopBoundary(program);
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		return new Fixture(program, analysis, model,
			ExactPhysicalCostModel.physicalCostSurface(analysis, model));
	}

	private static List<ExactPhysicalModel.DecisionDomain> tableDomains(Fixture fixture) {
		return fixture.model.domains().stream().filter(domain ->
			fixture.analysis.hop(domain.node().key()).orElse(null) instanceof TernaryOp ternary
				&& ternary.getOp() == OpOp3.CTABLE).toList();
	}

	private static int directLout(ExactPhysicalModel.DecisionDomain domain) {
		for(int value = 0; value < domain.alternatives().size(); value++) {
			var alternative = domain.alternatives().get(value);
			if(alternative.state().execType() == ExecType.FED
				&& alternative.state().output() == FederatedOutput.LOUT
				&& directInput(alternative, 0) && directInput(alternative, 1))
				return value;
		}
		throw new AssertionError("fixture has no native direct-direct CTABLE LOUT");
	}

	private static boolean directInput(ExactPhysicalModel.Alternative alternative, int position) {
		return alternative.inputAuthorities().stream().anyMatch(authority ->
			authority.inputPosition() == position
				&& authority.kind() == ExactPhysicalModel.InputAuthorityKind.DIRECT_FOUT);
	}

	private static int cpAlternative(ExactPhysicalModel.DecisionDomain domain) {
		for(int value = 0; value < domain.alternatives().size(); value++)
			if(domain.alternatives().get(value).state().execType() == ExecType.CP)
				return value;
		return -1;
	}

	private static Solved solve(Fixture fixture,
		Map<ExactPhysicalModel.DecisionDomain,Integer> pins) {
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>(fixture.model.hardFactors());
		factors.addAll(fixture.surface.factors());
		for(var pin : pins.entrySet()) {
			int selected = pin.getValue();
			factors.add(ExactCategoricalSolver.Factor.lazy(List.of(pin.getKey().variable()),
				assignment -> assignment[0] == selected ? 0 : Double.POSITIVE_INFINITY));
		}
		ExactCategoricalSolver.Result result = ExactCategoricalSolver.solve(
			fixture.model.variables(), factors, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		List<Integer> values = result.assignmentInVariableOrder();
		Assert.assertEquals(fixture.surface.evaluateCanonical(values),
			Double.doubleToRawLongBits(result.objective()));
		return new Solved(result, values, fixture.model.physicalSelection(result));
	}

	private static ExactPhysicalModel.Alternative selected(Solved solved,
		ExactPhysicalModel.DecisionDomain domain) {
		return solved.selection.alternativesInDecisionOrder().stream()
			.filter(alternative -> alternative.decision() == domain.node().key())
			.findFirst().orElseThrow();
	}

	private static void assertDirectRowInput(Fixture fixture, Solved solved,
		ExactPhysicalModel.Alternative owner, int position, String name) {
		var authority = owner.inputAuthorities().stream()
			.filter(candidate -> candidate.inputPosition() == position).findFirst().orElseThrow();
		Assert.assertEquals(ExactPhysicalModel.InputAuthorityKind.DIRECT_FOUT, authority.kind());
		Assert.assertEquals(FType.ROW, authority.expectedFType());
		ExactPhysicalModel.Alternative producer = solved.selection.alternativesInDecisionOrder().stream()
			.filter(candidate -> candidate.decision() == authority.sourceDecision()).findFirst().orElseThrow();
		Assert.assertEquals(name, fixture.analysis.hop(authority.sourceDecision()).orElseThrow().getName());
		Assert.assertEquals(ExecType.FED, producer.state().execType());
		Assert.assertEquals(FederatedOutput.FOUT, producer.state().output());
		Assert.assertEquals(FType.ROW, producer.state().fType());
	}

	private static List<ExactPhysicalCostModel.PhysicalTransferKey> ctableSecondaryGets(
		Fixture fixture, List<ExactPhysicalModel.DecisionDomain> tables) {
		return fixture.surface.transferKeys().stream().filter(key ->
			key.direction() == ExactPhysicalCostModel.Direction.DOWNLOAD
				&& key.boundaryMode() == ExactPhysicalCostModel.BoundaryMode.ANCHOR_TRANSFER
				&& key.endpoints().stream().anyMatch(endpoint -> endpoint.inputPosition() == 1
					&& tables.stream().anyMatch(table -> endpoint.consumer() == table.node().key())))
			.toList();
	}

	private static List<ExactPhysicalCostModel.PhysicalTransferKey> activeCtableSecondaryGets(
		Fixture fixture, List<Integer> values, List<ExactPhysicalModel.DecisionDomain> tables) {
		return ctableSecondaryGets(fixture, tables).stream()
			.filter(get -> materializationCost(fixture, values, get) > 0).toList();
	}

	private static double materializationCost(Fixture fixture, List<Integer> values,
		ExactPhysicalCostModel.PhysicalTransferKey get) {
		return materializationCost(fixture, values, List.of(get));
	}

	private static double materializationCost(Fixture fixture, List<Integer> values,
		List<ExactPhysicalCostModel.PhysicalTransferKey> gets) {
		List<List<ExactCategoricalSolver.Variable>> endpointScopes = gets.stream().map(get ->
			get.endpoints().stream().flatMap(endpoint -> fixture.model.domains().stream()
				.filter(domain -> domain.node().key() == endpoint.producer()
					|| domain.node().key() == endpoint.consumer())
				.map(ExactPhysicalModel.DecisionDomain::variable)).distinct().toList()).toList();
		List<ExactPhysicalCostModel.PhysicalContribution> matches = fixture.surface.contributions().stream()
			.filter(contribution -> endpointScopes.stream().anyMatch(endpointVariables ->
				contribution.factor().scope().size() == endpointVariables.size()
					&& endpointVariables.stream().allMatch(contribution.factor().scope()::contains)))
			.distinct().toList();
		Assert.assertFalse("transfer keys must retain canonical contributions|keys=" + gets,
			matches.isEmpty());
		return matches.stream().mapToDouble(contribution ->
			fixture.surface.evaluateContributionCanonical(contribution, values)).sum();
	}

	private static String twoSourceScript(String suffix) {
		return fed("A", "A") + fed("B", "B") + suffix;
	}

	private static String fed(String name, String path) {
		return name + "=federated(addresses=list(\"localhost:1234/" + path
			+ "1\",\"localhost:1235/" + path + "2\"),ranges=list(list(0,0),list(2,1),"
			+ "list(2,0),list(4,1)));";
	}

	private record Fixture(DMLProgram program, PlacementAnalysis analysis,
		ExactPhysicalModel model, ExactPhysicalCostModel.PhysicalCostSurface surface) { }
	private record Solved(ExactCategoricalSolver.Result result, List<Integer> values,
		ExactPhysicalModel.PhysicalSelection selection) { }
	private record LoopCost(double get, double invocation) { }
}
